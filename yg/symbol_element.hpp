#pragma once

#include "overlay_element.hpp"

namespace yg
{
  struct ResourceStyle;
  class Skin;
  class ResourceStyleCache;

  class SymbolElement : public OverlayElement
  {
  private:

    string m_symbolName;
    m2::RectU m_symbolRect;

    mutable vector<m2::AnyRectD> m_boundRects;

    m2::AnyRectD const boundRect() const;

  public:

    typedef OverlayElement base_t;

    struct Params : public base_t::Params
    {
      Skin * m_skin;
      string m_symbolName;
    };

    SymbolElement(Params const & p);
    SymbolElement(SymbolElement const & se, math::Matrix<double, 3, 3> const & m);

    vector<m2::AnyRectD> const & boundRects() const;
    void draw(gl::OverlayRenderer * s, math::Matrix<double, 3, 3> const & m) const;

    uint32_t styleID() const;

    OverlayElement * clone(math::Matrix<double, 3, 3> const & m) const;
  };
}
